export type MenuItem = {
  id: number
  name: string
  price: number
  soldOut: boolean
  imageUrl?: string
  visible: boolean
  category?: 'MAIN' | 'SIDE' | 'DRINK'
}
